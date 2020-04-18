provider "aws" {
  region = "ap-southeast-2"
}

data "aws_ami" "amzn2" {
  most_recent = true

  filter {
    name   = "name"
    values = ["amzn2-ami-hvm-*-x86_64-gp2"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  owners = ["amazon"]
}

data "aws_ami" "centos" {
  most_recent = true

  filter {
    name   = "name"
    values = ["CentOS Linux 7 x86_64 HVM EBS*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  owners = ["679593333241"]
}

#resource "aws_instance" "server" {
#  ami           = data.aws_ami.amzn2.id
#  instance_type = "t3.small"
#
##  user_data =<<EOF
###!bin/bash
##useradd -o -u 1000 -g 1000 -d /home/centos -M ec2-user
##EOF
#
#  tags = {
#    Name = "authfed"
#  }
#
#  key_name = "qwerty"
#
#  subnet_id = "subnet-60500504"
#
#  vpc_security_group_ids = [
#    "sg-48ea542c",
#  ]
#
#  root_block_device {
#    volume_type = "gp2"
#    volume_size = 8
#    delete_on_termination = true
#  }
#}
#
#output "server_addr" {
#  value = aws_instance.server.public_ip
#}
